package com.opengym.core.ui.component

import com.opengym.core.domain.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a muscle breakdown becomes bars.
 *
 * The one decision worth testing here is the scaling, because it is the one that could quietly
 * become wrong in a way nobody sees: `MuscleVolumeCalculator` credits a full share to every
 * primary link and half to every secondary one, so its figures sum to more than the volume that
 * produced them. A bar drawn as a share of that sum would state a whole that does not exist,
 * and would look perfectly reasonable doing it.
 */
class MuscleContributionTest {

    private val chest = MuscleGroup(id = 1, name = "CHEST")
    private val shoulders = MuscleGroup(id = 2, name = "SHOULDERS")
    private val triceps = MuscleGroup(id = 3, name = "TRICEPS")

    @Test
    fun `bars are scaled against the hardest-worked muscle, not against the total`() {
        val contributions = muscleContributions(
            volumeByMuscle = linkedMapOf(chest to 1_000L, triceps to 250L),
            primaryMuscleIds = setOf(chest.id),
        )

        // Were these shares of the sum they would be 0.8 and 0.2. Against the top muscle they
        // say the thing the data supports: the triceps took a quarter of what the chest did.
        assertEquals(1f, contributions[0].share)
        assertEquals(0.25f, contributions[1].share)
    }

    @Test
    fun `the calculator's order is preserved, because it is the order to draw`() {
        val contributions = muscleContributions(
            volumeByMuscle = linkedMapOf(chest to 900L, shoulders to 600L, triceps to 300L),
            primaryMuscleIds = emptySet(),
        )

        assertEquals(listOf(chest, shoulders, triceps), contributions.map { it.muscle })
    }

    @Test
    fun `a muscle any exercise worked as a prime mover is marked as one`() {
        val contributions = muscleContributions(
            volumeByMuscle = linkedMapOf(chest to 900L, triceps to 300L),
            primaryMuscleIds = setOf(chest.id),
        )

        assertTrue(contributions.first { it.muscle == chest }.isPrimary)
        assertTrue(!contributions.first { it.muscle == triceps }.isPrimary)
    }

    @Test
    fun `a window where nothing carried load draws every bar full rather than dividing by zero`() {
        // A session of bodyweight holds attributes zero everywhere. Nothing distinguishes those
        // muscles, and drawing them all full is the honest reading of that.
        val contributions = muscleContributions(
            volumeByMuscle = linkedMapOf(chest to 0L, triceps to 0L),
            primaryMuscleIds = emptySet(),
        )

        assertEquals(listOf(1f, 1f), contributions.map { it.share })
    }

    @Test
    fun `nothing worked is no rows, not a row saying so`() {
        assertTrue(muscleContributions(emptyMap(), emptySet()).isEmpty())
    }

    @Test
    fun `every group is kept, because how many fit is the card's question and not this one's`() {
        // `SessionMusclesCard` trims to what it has room for and counts the rest as `+3 more`,
        // which it can only do if it is handed all of them.
        val contributions = muscleContributions(
            volumeByMuscle = linkedMapOf(chest to 900L, shoulders to 600L, triceps to 300L),
            primaryMuscleIds = emptySet(),
        )

        assertEquals(listOf(chest, shoulders, triceps), contributions.map { it.muscle })
    }
}
