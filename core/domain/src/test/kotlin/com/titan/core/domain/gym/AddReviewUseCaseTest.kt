package com.titan.core.domain.gym

import com.titan.core.domain.model.GymReview
import com.titan.core.domain.model.ReviewError
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.AwardXpUseCase
import com.titan.core.domain.usecase.FakeGamificationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddReviewUseCaseTest {

    private val reviews = FakeGymReviewRepository()
    private val gamification = FakeGamificationRepository()
    private val addReview = AddReviewUseCase(
        reviews = reviews,
        xpHook = GymXpHook(FakeGymMilestoneRepository(), AwardXpUseCase(gamification)),
        clock = Clock { NOW },
    )

    @Test
    fun `a rating with a comment is stored and stamped with the clock`() =
        runTest {
            val review = addReview(gymId = 7, rating = 4, comment = "Plenty of racks").getOrThrow()

            assertEquals(GymReview(7, 4, "Plenty of racks", NOW), review)
            assertEquals(review, reviews.stored[7L])
        }

    @Test
    fun `every rating from 1 to 5 is accepted`() =
        runTest {
            for (rating in 1..5) {
                assertTrue(addReview(gymId = rating.toLong(), rating = rating).isSuccess)
            }
        }

    @Test
    fun `a comment is optional, and a blank one is stored as none`() =
        runTest {
            assertNull(addReview(1, 5).getOrThrow().comment)
            assertNull(addReview(2, 5, "   ").getOrThrow().comment)
        }

    @Test
    fun `a comment is trimmed before it is stored`() =
        runTest {
            assertEquals("Clean", addReview(1, 5, "  Clean \n").getOrThrow().comment)
        }

    @Test
    fun `a comment of exactly 500 characters is accepted, 501 is refused`() =
        runTest {
            assertTrue(addReview(1, 3, "x".repeat(500)).isSuccess)

            val error = addReview(2, 3, "x".repeat(501)).exceptionOrNull()
            assertEquals(ReviewError.CommentTooLong(501), error)
            assertNull(reviews.stored[2L])
        }

    @Test
    fun `a second review of the same gym is refused and the first stands`() =
        runTest {
            val first = addReview(1, 5, "Great").getOrThrow()

            val error = addReview(1, 1, "Changed my mind").exceptionOrNull()

            assertEquals(ReviewError.AlreadyReviewed(1), error)
            assertEquals(first, reviews.stored[1L])
        }

    @Test
    fun `a rating outside 1 to 5 is refused`() =
        runTest {
            for (rating in listOf(0, 6, -1, Int.MAX_VALUE)) {
                assertEquals(ReviewError.InvalidRating(rating), addReview(1, rating).exceptionOrNull())
            }
            assertTrue(reviews.stored.isEmpty())
        }

    @Test
    fun `a refused review pays no XP`() =
        runTest {
            addReview(1, 9)
            addReview(1, 3, "x".repeat(501))

            assertEquals(0L, gamification.totalXp)
        }

    @Test
    fun `the first review pays 30 XP and later ones for other gyms pay nothing`() =
        runTest {
            addReview(1, 5)
            addReview(2, 4)
            addReview(1, 4)

            assertEquals(30L, gamification.totalXp)
        }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
