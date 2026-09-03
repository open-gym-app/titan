package com.opengym.feature.progression.achievements

import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.model.UnlockedAchievement
import com.opengym.feature.progression.quests.FakeGamificationReads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The achievements shelf's state assembly: one read, and the catalogue walked against it.
 *
 * The first three cases were `QuestLogViewModelTest`'s while the grid lived on that screen. They
 * moved with it, unchanged in substance: a medal for every catalogue entry held or not, an id on
 * file that matches nothing producing no medal, and an unlock arriving while the screen is open.
 * The rest cover what the medals added: the date a milestone was struck, and which one the hero
 * leads with.
 *
 * Collected in a launched job rather than read off `.value`, because `WhileSubscribed` produces
 * nothing at all without a subscriber.
 */
class AchievementsViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val gamification = FakeGamificationReads()

    @Test
    fun `the grid has a cell for every catalogue achievement, held or not`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session"))

            val state = collect()

            assertEquals(Achievements.ALL.size, state().entries.size)
            assertEquals(Achievements.ALL.map { it.id }, state().entries.map { it.achievement.id })
        }

    @Test
    fun `an achievement on file is marked held and the rest are not`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session"), unlock("first_record"))

            val state = collect()

            assertEquals(2, state().unlockedCount)
            assertEquals(
                setOf("first_session", "first_record"),
                state().entries.filter { it.isUnlocked }.map { it.achievement.id }.toSet(),
            )
        }

    @Test
    fun `an id on file that matches no catalogue entry puts no cell in the grid`() =
        runTest {
            // A milestone renamed without a migration. It must not produce a cell with no
            // definition behind it, and it must not inflate the count.
            gamification.unlocked.value = listOf(unlock("a_milestone_from_an_older_build"))

            val state = collect()

            assertEquals(Achievements.ALL.size, state().entries.size)
            assertEquals(0, state().unlockedCount)
        }

    @Test
    fun `a brand-new user gets every cell, locked`() =
        runTest {
            val state = collect()

            assertEquals(0, state().unlockedCount)
            assertTrue("no cell is held", state().entries.none { it.isUnlocked })
            assertEquals(Achievements.ALL.size, state().entries.size)
        }

    @Test
    fun `an achievement unlocked while the screen is open fills its cell in`() =
        runTest {
            val state = collect()
            assertEquals(0, state().unlockedCount)

            gamification.unlocked.value = listOf(unlock("first_session"))

            assertEquals(1, state().unlockedCount)
        }

    @Test
    fun `a held achievement carries the date it was earned`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session", at = Instant.ofEpochMilli(DAY_MILLIS)))

            val state = collect()

            val held = state().entries.first { it.achievement.id == "first_session" }
            assertEquals(DAY_MILLIS, held.unlockedAt)
            assertEquals(null, state().entries.first { it.achievement.id == "ten_sessions" }.unlockedAt)
        }

    @Test
    fun `the newest unlock is the one the screen leads with`() =
        runTest {
            // Out of catalogue order on purpose: the hero is the most recent medal, not the
            // furthest down the catalogue. See `AchievementsUiState.latest`.
            gamification.unlocked.value = listOf(
                unlock("first_record", at = Instant.ofEpochMilli(2 * DAY_MILLIS)),
                unlock("first_session", at = Instant.ofEpochMilli(DAY_MILLIS)),
            )

            val state = collect()

            assertEquals("first_record", state().latest?.achievement?.id)
        }

    @Test
    fun `two milestones earned at once lead with the dearer of them`() =
        runTest {
            // A first workout unlocks the first session milestone and the first record together.
            // The tie breaks on catalogue order, which evaluates the cheapest first — so the one
            // to lead with is the last of them, not the first.
            gamification.unlocked.value = listOf(
                unlock("first_session", at = Instant.ofEpochMilli(DAY_MILLIS)),
                unlock("first_record", at = Instant.ofEpochMilli(DAY_MILLIS)),
            )

            val state = collect()

            assertEquals("first_record", state().latest?.achievement?.id)
        }

    @Test
    fun `a user who holds nothing has no medal to lead with`() =
        runTest {
            val state = collect()

            assertEquals(null, state().latest)
        }

    @Test
    fun `nothing has answered before the first emission`() {
        val viewModel = AchievementsViewModel(gamificationRepository = gamification)

        assertTrue("the initial value is the loading one", viewModel.uiState.value.isLoading)
        assertEquals(emptyList<AchievementEntry>(), viewModel.uiState.value.entries)
    }

    /**
     * The state, kept live for the duration of the test.
     *
     * The collector runs on an unconfined dispatcher so `WhileSubscribed` starts sharing on the
     * spot; a standard one would leave the flow unsubscribed until the test scheduler advanced,
     * and every assertion would read the initial value.
     */
    private fun TestScope.collect(): () -> AchievementsUiState {
        val viewModel = AchievementsViewModel(gamificationRepository = gamification)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return { viewModel.uiState.value }
    }

    private fun unlock(
        id: String,
        at: Instant = Instant.EPOCH,
    ): UnlockedAchievement = UnlockedAchievement(achievementId = id, unlockedAt = at)

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
